cmake_minimum_required(VERSION 3.22.1)
project(AdbSshTunnel)
include(${CMAKE_CURRENT_LIST_DIR}/../vendor/native.cmake)
add_executable(tunnel-server src/main/cpp/tunnel-server.c)
target_include_directories(tunnel-server PRIVATE ${DROPBEAR_INCLUDES}
    ${CMAKE_CURRENT_LIST_DIR}/../vendor/Sshd4a/app/src/main/cpp)
target_link_libraries(tunnel-server tommath tomcrypt dropbear z)
target_link_options(tunnel-server PRIVATE -Wl,-z,max-page-size=16384)
if(NOT DEFINED TUNNEL_JNI_DIR)
    message(FATAL_ERROR "TUNNEL_JNI_DIR must identify the distribution's native directory")
endif()
add_custom_command(TARGET tunnel-server POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${TUNNEL_JNI_DIR}/${ANDROID_ABI}"
    COMMAND ${CMAKE_COMMAND} -E copy "$<TARGET_FILE:tunnel-server>"
        "${TUNNEL_JNI_DIR}/${ANDROID_ABI}/libtunnel-server.so")
add_executable(root-controller src/main/cpp/root-controller.c)
target_compile_options(root-controller PRIVATE -Wall -Wextra -Werror)
target_link_options(root-controller PRIVATE -Wl,-z,max-page-size=16384)
if(NOT DEFINED ROOT_CONTROLLER_ASSETS_DIR)
    message(FATAL_ERROR "ROOT_CONTROLLER_ASSETS_DIR must identify the distribution's assets directory")
endif()
set(CONTROLLER_ASSETS "${ROOT_CONTROLLER_ASSETS_DIR}/${ANDROID_ABI}")
add_custom_command(TARGET root-controller POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CONTROLLER_ASSETS}"
    COMMAND ${CMAKE_COMMAND} -E copy "$<TARGET_FILE:root-controller>" "${CONTROLLER_ASSETS}/root-controller")
