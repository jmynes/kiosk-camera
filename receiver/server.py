#!/usr/bin/env python3
"""HTTPS receiver for KioskCamera uploads."""

import os
import re
import ssl
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path

UPLOAD_DIR = Path.home() / "uploads"
CERT_DIR = Path(__file__).parent / "certs"
PORT = 8443


class UploadHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/upload":
            self.send_error(404)
            return

        content_type = self.headers.get("Content-Type", "")
        if "multipart/form-data" not in content_type:
            self.send_error(400, "Expected multipart/form-data")
            return

        # Extract boundary
        match = re.search(r'boundary=(.+)', content_type)
        if not match:
            self.send_error(400, "No boundary found")
            return
        boundary = match.group(1).strip().encode()

        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length)

        # Parse multipart manually
        saved = []
        parts = body.split(b"--" + boundary)
        for part in parts:
            if b"Content-Disposition" not in part:
                continue

            # Extract filename
            disp_match = re.search(rb'filename="([^"]+)"', part)
            if not disp_match:
                continue
            filename = disp_match.group(1).decode()

            # Extract file data (after double CRLF)
            header_end = part.find(b"\r\n\r\n")
            if header_end == -1:
                continue
            file_data = part[header_end + 4:]
            # Remove trailing \r\n
            if file_data.endswith(b"\r\n"):
                file_data = file_data[:-2]

            dest = UPLOAD_DIR / os.path.basename(filename)
            with open(dest, "wb") as f:
                f.write(file_data)
            saved.append(filename)
            print(f"  Saved: {dest} ({len(file_data)} bytes)")

        if saved:
            body = f"OK: {', '.join(saved)}\n".encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_error(400, "No file found in upload")

    def do_GET(self):
        if self.path == "/health":
            self.send_response(200)
            body = b"OK\n"
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_error(404)

    def log_message(self, format, *args):
        print(f"[{self.log_date_time_string()}] {format % args}")


def main():
    UPLOAD_DIR.mkdir(exist_ok=True)

    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(
        certfile=CERT_DIR / "server.pem",
        keyfile=CERT_DIR / "server.key",
    )

    server = HTTPServer(("0.0.0.0", PORT), UploadHandler)
    server.socket = context.wrap_socket(server.socket, server_side=True)

    print(f"KioskCamera receiver listening on https://0.0.0.0:{PORT}")
    print(f"Uploads saved to: {UPLOAD_DIR}")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")
        server.shutdown()


if __name__ == "__main__":
    main()
