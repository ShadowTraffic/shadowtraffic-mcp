# ShadowTraffic MCP Server

A Model Context Protocol (MCP) server that provides tools for generating test data with ShadowTraffic.

## Tools

- **bootstrap**: Convert Avro or JSON Schema into ShadowTraffic configuration
- **run-shadowtraffic**: Execute ShadowTraffic with a given configuration

## Building

### Prerequisites

- Java 11+
- Clojure CLI
- Docker (for ShadowTraffic tools)
- ShadowTraffic license environment variables

### Build Steps

1. Clone the repository
2. Build the uberjar:
   ```bash
   ./build.sh
   ```
   Or manually:
   ```bash
   clojure -T:build uber
   ```

This creates `target/modex-0.3.0.jar`

## Configuration

### ShadowTraffic License

Set these environment variables before running:
```bash
export LICENSE_ID="your-license-id"
export LICENSE_EMAIL="your-email"
export LICENSE_ORGANIZATION="your-org"
export LICENSE_EDITION="your-edition" 
export LICENSE_EXPIRATION="your-expiration"
export LICENSE_SIGNATURE="your-signature"
```

### Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "shadowtraffic": {
      "command": "java",
      "args": ["-jar", "/path/to/shadowtraffic-mcp/target/modex-0.3.0.jar"],
      "env": {
        "LICENSE_ID": "your-license-id",
        "LICENSE_EMAIL": "your-email",
        "LICENSE_ORGANIZATION": "your-org",
        "LICENSE_EDITION": "your-edition",
        "LICENSE_EXPIRATION": "your-expiration",
        "LICENSE_SIGNATURE": "your-signature"
      }
    }
  }
}
```

Restart Claude Desktop to load the server.

## Development

### nREPL Support

Set environment variables to enable nREPL:
```bash
export NREPL_BIND_ADDRESS=127.0.0.1
export NREPL_PORT=4001
```

### Testing

```bash
clojure -X:test
```

## License

GPLv3 for non-commercial use. Commercial license available for $20.
