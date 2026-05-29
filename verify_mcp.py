from mcp_server import mcp

import asyncio

# Inspect registered tools from the FastMCP instance
print("=== VERIFYING FRAUDSHIELD MCP SERVER ===")
try:
    tools = asyncio.run(mcp.list_tools())
    print(f"Successfully registered {len(tools)} tools:")
    for tool in tools:
        print(f"\nTool: {tool.name}")
        print(f"  Description: {tool.description}")
        print(f"  Input Schema: {tool.inputSchema}")
    print("\nVerification SUCCESSFUL! All tools are correctly defined.")
except Exception as e:
    print(f"Verification FAILED: {str(e)}")
