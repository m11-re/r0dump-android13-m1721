/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Thin stdio<->socket relay for the R0dumpManager MCP server.
//
// The manager app hosts an MCP (JSON-RPC 2.0) server on an abstract-namespace
// AF_UNIX socket named "r0dump_mcp" (created by android.net.LocalServerSocket,
// which prefixes the sun_path with a NUL byte). An MCP client on the host drives
// it over adb with:
//
//     adb -s <serial> shell r0dump_mcp_bridge
//
// With a piped (non-tty) stdin, adb shell gives this process the client's
// stdin/stdout as a raw byte channel (adb exec-out does not forward stdin on all
// host/adb builds, so shell is preferred). This binary forwards those bytes to
// and from the app socket unchanged. The framing (newline-delimited JSON-RPC) is
// the app's concern -- the bridge is deliberately protocol-agnostic.

#include <errno.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

namespace {

constexpr char kSocketName[] = "r0dump_mcp";
constexpr size_t kBufferSize = 65536;

int ConnectAbstractSocket(const char* name) {
  const int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (fd < 0) {
    fprintf(stderr, "r0dump_mcp_bridge: socket: %s\n", strerror(errno));
    return -1;
  }
  sockaddr_un address{};
  address.sun_family = AF_UNIX;
  // Abstract namespace: leading NUL, then the name (no trailing NUL counted).
  const size_t name_length = strlen(name);
  if (name_length + 1 > sizeof(address.sun_path)) {
    fprintf(stderr, "r0dump_mcp_bridge: socket name too long\n");
    close(fd);
    return -1;
  }
  address.sun_path[0] = '\0';
  memcpy(address.sun_path + 1, name, name_length);
  const socklen_t length =
      static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + name_length);
  if (TEMP_FAILURE_RETRY(connect(fd, reinterpret_cast<sockaddr*>(&address),
                                 length)) != 0) {
    fprintf(stderr,
            "r0dump_mcp_bridge: connect(@%s): %s (is the MCP switch on in "
            "R0dumpManager?)\n",
            name, strerror(errno));
    close(fd);
    return -1;
  }
  return fd;
}

// Copy everything available on src into dst. Returns false when the stream is
// closed (EOF) or a fatal error occurs, so the caller can tear the relay down.
bool Pump(int src, int dst) {
  char buffer[kBufferSize];
  const ssize_t count = TEMP_FAILURE_RETRY(read(src, buffer, sizeof(buffer)));
  if (count == 0) {
    return false;
  }
  if (count < 0) {
    fprintf(stderr, "r0dump_mcp_bridge: read: %s\n", strerror(errno));
    return false;
  }
  size_t written = 0;
  while (written < static_cast<size_t>(count)) {
    const ssize_t bytes = TEMP_FAILURE_RETRY(
        write(dst, buffer + written, static_cast<size_t>(count) - written));
    if (bytes <= 0) {
      fprintf(stderr, "r0dump_mcp_bridge: write: %s\n", strerror(errno));
      return false;
    }
    written += static_cast<size_t>(bytes);
  }
  return true;
}

}  // namespace

int main() {
  const int socket_fd = ConnectAbstractSocket(kSocketName);
  if (socket_fd < 0) {
    return 1;
  }

  pollfd fds[2];
  fds[0] = {.fd = STDIN_FILENO, .events = POLLIN, .revents = 0};
  fds[1] = {.fd = socket_fd, .events = POLLIN, .revents = 0};

  // Once stdin closes we stop watching it but keep relaying the socket for a
  // short idle window, so a request piped in without a persistent stdin (e.g.
  // a one-shot probe) still receives its reply before we exit.
  constexpr int kDrainIdleMs = 2000;
  int status = 0;
  bool stdin_open = true;
  while (true) {
    const int timeout = stdin_open ? -1 : kDrainIdleMs;
    const int ready = TEMP_FAILURE_RETRY(poll(fds, 2, timeout));
    if (ready < 0) {
      fprintf(stderr, "r0dump_mcp_bridge: poll: %s\n", strerror(errno));
      status = 1;
      break;
    }
    if (ready == 0) {
      break;  // Idle after stdin closed: assume the exchange is done.
    }
    if ((fds[1].revents & POLLIN) != 0 && !Pump(socket_fd, STDOUT_FILENO)) {
      break;  // App closed the socket.
    }
    if (stdin_open && (fds[0].revents & POLLIN) != 0 &&
        !Pump(STDIN_FILENO, socket_fd)) {
      stdin_open = false;  // Client closed stdin; drain the socket, then exit.
      fds[0].fd = -1;
      fds[0].events = 0;
    }
    if ((fds[1].revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
      while (Pump(socket_fd, STDOUT_FILENO)) {
      }
      break;  // Socket gone.
    }
    if (stdin_open && (fds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
      stdin_open = false;
      fds[0].fd = -1;
      fds[0].events = 0;
    }
  }

  close(socket_fd);
  return status;
}
