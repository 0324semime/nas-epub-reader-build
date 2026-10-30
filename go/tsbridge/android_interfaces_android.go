//go:build android && cgo

// Adapted from the verified GameTrade Mobile embedded Tailscale bridge.\n// Android's app sandbox denies the NETLINK_ROUTE requests made by Go's
// net.Interfaces. bionic's getifaddrs uses a permitted interface enumeration
// path, allowing Tailscale netmon to see network interfaces without a VPN.
package tsbridge

/*
#cgo CFLAGS: -D_DEFAULT_SOURCE
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <sys/socket.h>
*/
import "C"

import (
	"fmt"
	"net"
	"unsafe"

	"tailscale.com/net/netmon"
)

func init() {
	// Register BEFORE tsnet.Server.Start, using Tailscale's public hook.
	// Avoid net.Interfaces here: Android 11+ can deny NETLINK_ROUTE.
	netmon.RegisterInterfaceGetter(enumerateAndroidInterfaces)
}

func enumerateAndroidInterfaces() ([]netmon.Interface, error) {
	var first *C.struct_ifaddrs
	if ret := C.getifaddrs(&first); ret != 0 {
		return nil, fmt.Errorf("android getifaddrs failed (code %d)", int(ret))
	}
	defer C.freeifaddrs(first)

	interfaces := make([]netmon.Interface, 0, 8)
	positions := make(map[string]int)
	for item := first; item != nil; item = item.ifa_next {
		if item.ifa_name == nil {
			continue
		}
		name := C.GoString(item.ifa_name)
		if name == "" {
			continue
		}
		pos, exists := positions[name]
		if !exists {
			flags := net.Flags(0)
			f := uint(item.ifa_flags)
			if f&uint(C.IFF_UP) != 0 {
				flags |= net.FlagUp
			}
			if f&uint(C.IFF_BROADCAST) != 0 {
				flags |= net.FlagBroadcast
			}
			if f&uint(C.IFF_LOOPBACK) != 0 {
				flags |= net.FlagLoopback
			}
			if f&uint(C.IFF_POINTOPOINT) != 0 {
				flags |= net.FlagPointToPoint
			}
			if f&uint(C.IFF_MULTICAST) != 0 {
				flags |= net.FlagMulticast
			}
			if f&uint(C.IFF_RUNNING) != 0 {
				flags |= net.FlagRunning
			}
			idx := int(C.if_nametoindex(item.ifa_name))
			pos = len(interfaces)
			positions[name] = pos
			interfaces = append(interfaces, netmon.Interface{
				Interface: &net.Interface{Name: name, Index: idx, Flags: flags},
				// Non-nil AltAddrs prevents Go's net.Interface.Addrs() from
				// falling back to restricted netlink calls.
				AltAddrs: make([]net.Addr, 0, 2),
			})
		}
		if item.ifa_addr == nil {
			continue
		}
		switch int(item.ifa_addr.sa_family) {
		case C.AF_INET:
			sa := (*C.struct_sockaddr_in)(unsafe.Pointer(item.ifa_addr))
			address := net.IP(C.GoBytes(unsafe.Pointer(&sa.sin_addr), 4))
			mask := net.CIDRMask(32, 32)
			if item.ifa_netmask != nil {
				sn := (*C.struct_sockaddr_in)(unsafe.Pointer(item.ifa_netmask))
				mask = net.IPMask(C.GoBytes(unsafe.Pointer(&sn.sin_addr), 4))
			}
			interfaces[pos].AltAddrs = append(interfaces[pos].AltAddrs, &net.IPNet{IP: address, Mask: mask})
		case C.AF_INET6:
			sa := (*C.struct_sockaddr_in6)(unsafe.Pointer(item.ifa_addr))
			address := net.IP(C.GoBytes(unsafe.Pointer(&sa.sin6_addr), 16))
			mask := net.CIDRMask(128, 128)
			if item.ifa_netmask != nil {
				sn := (*C.struct_sockaddr_in6)(unsafe.Pointer(item.ifa_netmask))
				mask = net.IPMask(C.GoBytes(unsafe.Pointer(&sn.sin6_addr), 16))
			}
			interfaces[pos].AltAddrs = append(interfaces[pos].AltAddrs, &net.IPNet{IP: address, Mask: mask})
		}
	}
	if len(interfaces) == 0 {
		return nil, fmt.Errorf("android getifaddrs returned no network interfaces")
	}
	return interfaces, nil
}
