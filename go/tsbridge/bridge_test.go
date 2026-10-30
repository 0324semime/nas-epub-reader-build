package tsbridge

import (
    "encoding/json"
    "testing"
)

func TestTargets(t *testing.T) {
    for _,tc:=range []struct{host string;want bool}{
        {"100.64.0.1",true},
        {"100.127.255.254",true},
        {"100.128.0.1",false},
        {"192.168.3.4",true},
        {"10.0.0.1",true},
        {"172.16.0.2",true},
        {"172.32.0.2",false},
        {"nas.my-tailnet.ts.net",true},
        {"www.example.com",false},
        {"localhost",false},
        {"127.0.0.1",false},
        {"8.8.8.8",false},
    } {
        if got:=validTarget(tc.host);got!=tc.want{t.Errorf("validTarget(%q)=%v want %v",tc.host,got,tc.want)}
    }
}
func TestStatusAndNoTunnelBeforeAuth(t *testing.T) {
    var state map[string]any
    if err:=json.Unmarshal([]byte(Status()),&state);err!=nil{t.Fatal(err)}
    if StartSmbTunnel("100.64.0.1")>=0{t.Fatal("started tunnel before login")}
}
