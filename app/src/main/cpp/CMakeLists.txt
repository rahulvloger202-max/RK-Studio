cmake_minimum_required(VERSION 3.4.1)
add_library(primx_pty SHARED terminal_pty.cpp)
find_library(log-lib log)
target_link_libraries(primx_pty ${log-lib})
