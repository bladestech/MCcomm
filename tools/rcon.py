"""Send a command to the local test server over RCON.
Usage: python tools/rcon.py <command...>   (reads password/port from run/server.properties)"""
import pathlib, socket, struct, sys

props = {}
for line in (pathlib.Path(__file__).resolve().parent.parent / "run" / "server.properties").read_text().splitlines():
    if "=" in line and not line.startswith("#"):
        k, v = line.split("=", 1)
        props[k] = v

def packet(req_id, kind, body):
    data = struct.pack("<ii", req_id, kind) + body.encode() + b"\x00\x00"
    return struct.pack("<i", len(data)) + data

def read(sock):
    size = struct.unpack("<i", sock.recv(4))[0]
    data = b""
    while len(data) < size:
        data += sock.recv(size - len(data))
    req_id, _ = struct.unpack("<ii", data[:8])
    return req_id, data[8:-2].decode(errors="replace")

with socket.create_connection(("127.0.0.1", int(props.get("rcon.port", 25575))), timeout=10) as s:
    s.sendall(packet(1, 3, props["rcon.password"]))
    if read(s)[0] == -1:
        sys.exit("RCON auth failed")
    s.sendall(packet(2, 2, " ".join(sys.argv[1:])))
    try:
        print(read(s)[1])
    except (socket.timeout, struct.error, ConnectionError):
        pass
