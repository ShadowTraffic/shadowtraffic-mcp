# ShadowTraffic MCP Server

A Model Context Protocol (MCP) server that provides tools for generating test data with [ShadowTraffic](https://shadowtraffic.io). Built in Clojure on the official [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk).

## Tools

- **bootstrap**: Convert an Avro or JSON Schema into a ShadowTraffic configuration. Takes an optional `target` connection type (defaults to `kafka`).
- **run-shadowtraffic**: Run ShadowTraffic with a given configuration. ShadowTraffic runs indefinitely unless the config sets limits like `maxEvents`, so the container is stopped after `timeout-seconds` (default 30) and the output so far is returned. Only the last ~20 KB of output is returned.

Non-zero exits are reported to the client as tool errors.

## Building

### Prerequisites

- Java 17+
- Clojure CLI
- Docker (for ShadowTraffic tools)
- ShadowTraffic license environment variables

### Build Steps

1. Clone the repository
2. Build the uberjar:
   ```bash
   clojure -T:build uber
   ```

This creates `target/shadowtraffic-mcp.jar`

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
      "args": ["-jar", "/path/to/shadowtraffic-mcp/target/shadowtraffic-mcp.jar"],
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

### Testing

```bash
clojure -X:test
```

The `run-container-test` integration test runs a small `alpine` container and is skipped when Docker isn't available.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
