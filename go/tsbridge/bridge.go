// Package tsbridge implements the EPUB reader's app-scoped Tailscale node.
// It listens only on loopback and forwards SMB TCP traffic through tsnet.
// No system VPN, inbound tailnet service, embedded key, or NAS credential.
package tsbridge

import (
    "context"
    "encoding/json"
    "errors"
    "fmt"
    "io"
    "net"
    "net/netip"
    "os"
    "path/filepath"
    "regexp"
    "strings"
    "sync"

    "tailscale.com/tsnet"
)

var authURL = regexp.MustCompile(`https://login.tailscale.com/[a-zA-Z0-9/_?=&.%-]+`)
var (
    rangeTailnet = netip.MustParsePrefix("100.64.0.0/10")
    rangeLAN10 = netip.MustParsePrefix("10.0.0.0/8")
    rangeLAN172 = netip.MustParsePrefix("172.16.0.0/12")
    rangeLAN192 = netip.MustParsePrefix("192.168.0.0/16")
)

type state struct {
    Phase string `json:"phase"`
    LoginURL string `json:"login_url,omitempty"`
    IP string `json:"ip,omitempty"`
    Error string `json:"error,omitempty"`
    Target string `json:"target,omitempty"`
    Port int `json:"port,omitempty"`
}
var shared struct {
    sync.Mutex
    server *tsnet.Server
    cancel context.CancelFunc
    generation uint64
    status state
    tunnel net.Listener
    tunnelHost string
    tunnelPort int
}
func init(){shared.status.Phase="idle"}

func Start(privateDir string) string {
    if privateDir=="" || !filepath.IsAbs(privateDir) {
        return "ERROR: absolute Android private directory required"
    }
    if err:=prepareStorage(privateDir);err!=nil{return "ERROR: "+err.Error()}
    shared.Lock()
    if shared.server!=nil {
        phase:=shared.status.Phase
        shared.Unlock()
        return "ALREADY_STARTED: "+phase
    }
    shared.generation++
    gen:=shared.generation
    ctx,cancel:=context.WithCancel(context.Background())
    srv:=&tsnet.Server{
        Dir: filepath.Join(privateDir,"tsnet-state"),
        Hostname: "nas-epub-reader",
        UserLogf:func(format string,args ...any) {
            msg:=fmt.Sprintf(format,args...)
            if url:=authURL.FindString(msg);url!=""{
                shared.Lock()
                if shared.generation==gen {
                    shared.status.LoginURL=url
                    if shared.status.Phase=="starting" {
                        shared.status.Phase="waiting_login"
                    }
                }
                shared.Unlock()
            }
        },
    }
    shared.server=srv
    shared.cancel=cancel
    shared.status=state{Phase:"starting"}
    shared.Unlock()
    go func(){
        if err:=srv.Start();err!=nil{
            fail(gen,err)
            cancel()
            return
        }
        shared.Lock()
        if shared.generation==gen && shared.status.Phase=="starting"{
            shared.status.Phase="waiting_login"
        }
        shared.Unlock()
        up,err:=srv.Up(ctx)
        if err!=nil {
            if ctx.Err()==nil{fail(gen,err)}
            return
        }
        shared.Lock()
        if shared.generation==gen {
            shared.status.Phase="connected"
            shared.status.Error=""
            if up!=nil && up.Self!=nil && len(up.Self.TailscaleIPs)>0 {
                shared.status.IP=up.Self.TailscaleIPs[0].String()
            }
        }
        shared.Unlock()
    }()
    return "STARTING"
}
func prepareStorage(dir string)error{
    for _,sub:=range []string{"tsnet-state","tsnet-logs","tsnet-tmp"}{
        path:=filepath.Join(dir,sub)
        if err:=os.MkdirAll(path,0700);err!=nil{return err}
    }
    if err:=os.Setenv("TS_LOGS_DIR",filepath.Join(dir,"tsnet-logs"));err!=nil{return err}
    if err:=os.Setenv("TMPDIR",filepath.Join(dir,"tsnet-tmp"));err!=nil{return err}
    return nil
}
func fail(gen uint64,err error){
    shared.Lock();defer shared.Unlock()
    if shared.generation==gen {
        shared.status.Phase="failed"
        shared.status.Error=err.Error()
    }
}
func Status()string {
    shared.Lock()
    snapshot:=shared.status
    shared.Unlock()
    raw,err:=json.Marshal(snapshot)
    if err!=nil{return `{"phase":"failed","error":"state unavailable"}`}
    return string(raw)
}
func validTarget(host string)bool {
    host=strings.ToLower(strings.TrimSpace(host))
    if ip,err:=netip.ParseAddr(host);err==nil {
        return ip.Is4() && (rangeTailnet.Contains(ip)||
            rangeLAN10.Contains(ip)||rangeLAN172.Contains(ip)||rangeLAN192.Contains(ip))
    }
    if len(host)<len("a.ts.net")||len(host)>253||!strings.HasSuffix(host,".ts.net"){return false}
    for _,part:=range strings.Split(host,"."){
        if part==""||len(part)>63||part[0]=='-'||part[len(part)-1]=='-' {return false}
        for _,ch:=range part {
            if(ch<'a'||ch>'z')&&(ch<'0'||ch>'9')&&ch!='-'{return false}
        }
    }
    return true
}
func ValidateTarget(host string)bool {return validTarget(host)}

// StartSmbTunnel opens a 127.0.0.1-only proxy to NAS TCP port 445 over
// this embedded Tailscale connection. Returned port is passed to SMBJ.
// All connections require the ordinary SMB username/password authentication.
func StartSmbTunnel(host string)int {
    host=strings.TrimSpace(host)
    if !validTarget(host){return -2}
    shared.Lock()
    srv:=shared.server
    phase:=shared.status.Phase
    if phase!="connected"||srv==nil {
        shared.Unlock()
        return -3
    }
    if shared.tunnel!=nil && shared.tunnelHost==host {
        result:=shared.tunnelPort
        shared.Unlock()
        return result
    }
    if shared.tunnel!=nil {
        shared.tunnel.Close()
        shared.tunnel=nil
        shared.tunnelPort=0
    }
    listen,err:=net.Listen("tcp4","127.0.0.1:0")
    if err!=nil{
        shared.status.Error="Could not start loopback TCP forwarder: "+err.Error()
        shared.Unlock()
        return -4
    }
    port:=listen.Addr().(*net.TCPAddr).Port
    gen:=shared.generation
    shared.tunnel=listen
    shared.tunnelHost=host
    shared.tunnelPort=port
    shared.status.Target=host
    shared.status.Port=port
    shared.Unlock()
    go func(){
        for {
            incoming,acceptErr:=listen.Accept()
            if acceptErr!=nil{return}
            go func(local net.Conn){
                defer local.Close()
                shared.Lock()
                valid:=shared.generation==gen && shared.server==srv && shared.tunnel==listen
                shared.Unlock()
                if !valid{return}
                ctx,cancel:=context.WithTimeout(context.Background(),15*time.Second)
                upstream,err:=srv.Dial(ctx,"tcp",net.JoinHostPort(host,"445"))
                cancel()
                if err!=nil{return}
                defer upstream.Close()
                done:=make(chan struct{},2)
                go func(){_,_=io.Copy(upstream,local);done<-struct{}{}}()
                go func(){_,_=io.Copy(local,upstream);done<-struct{}{}}()
                <-done
                // Closing both connections releases any remaining copier.
            }(incoming)
        }
    }()
    return port
}
func StopSmbTunnel(){
    shared.Lock()
    if shared.tunnel!=nil{shared.tunnel.Close()}
    shared.tunnel=nil
    shared.tunnelHost=""
    shared.tunnelPort=0
    shared.status.Target=""
    shared.status.Port=0
    shared.Unlock()
}
func Stop()string {
    shared.Lock()
    if shared.status.Phase=="starting" {
        shared.Unlock()
        return "WAIT: Tailscale initialization in progress"
    }
    if shared.server==nil{
        shared.Unlock()
        return "ALREADY_STOPPED"
    }
    server:=shared.server
    cancel:=shared.cancel
    if shared.tunnel!=nil{shared.tunnel.Close()}
    shared.tunnel=nil
    shared.tunnelHost=""
    shared.tunnelPort=0
    shared.generation++
    shared.server=nil
    shared.cancel=nil
    shared.status=state{Phase:"stopped"}
    shared.Unlock()
    if cancel!=nil{cancel()}
    if err:=server.Close();err!=nil && !errors.Is(err,net.ErrClosed){
        return "STOP_ERROR: "+err.Error()
    }
    return "STOPPED"
}
