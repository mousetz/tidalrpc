cmake_minimum_required(VERSION 3.22.1)
project(tidalrpc_bridge)

find_package(discord_partner_sdk REQUIRED CONFIG)
add_library(tidalrpc_bridge SHARED discord_bridge.cpp)
target_link_libraries(tidalrpc_bridge discord_partner_sdk::discord_partner_sdk log)
