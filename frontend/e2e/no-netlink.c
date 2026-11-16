// LD_PRELOAD shim for the e2e browser (#698): socket(AF_NETLINK, *, NETLINK_ROUTE) fails with
// EAFNOSUPPORT, so Chromium's AddressTrackerLinux takes its "assume always online" path and
// never sees the host's netlink stream. CI slots run with network_mode: host, so every docker
// bridge/veth another job creates on the same host is an address change to Chromium, which
// aborts in-flight requests with net::ERR_NETWORK_CHANGED — a Vite module load dies and the
// page never renders. ci/contract.sh builds it; playwright.config.ts preloads it into the
// browser process only (node/Vite keep their own netlink use).
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <linux/netlink.h>
#include <sys/socket.h>

int socket(int domain, int type, int protocol) {
  static int (*real_socket)(int, int, int);
  if (domain == AF_NETLINK && protocol == NETLINK_ROUTE) {
    errno = EAFNOSUPPORT;
    return -1;
  }
  if (!real_socket) real_socket = (int (*)(int, int, int))dlsym(RTLD_NEXT, "socket");
  return real_socket(domain, type, protocol);
}
