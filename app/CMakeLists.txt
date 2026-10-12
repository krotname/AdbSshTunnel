cmake_minimum_required(VERSION 3.22.1)
project(AdbSshTunnel)
include(${CMAKE_CURRENT_LIST_DIR}/../vendor/native.cmake)
add_executable(root-controller src/main/cpp/root-controller.c)
target_compile_options(root-controller PRIVATE -Wall -Wextra -Werror)
target_link_options(root-controller PRIVATE -Wl,-z,max-page-size=16384)
set(CONTROLLER_ASSETS "${CMAKE_CURRENT_LIST_DIR}/build/generated/root-controller-assets/${ANDROID_ABI}")
add_custom_command(TARGET root-controller POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CONTROLLER_ASSETS}"
    COMMAND ${CMAKE_COMMAND} -E copy "$<TARGET_FILE:root-controller>" "${CONTROLLER_ASSETS}/root-controller")
