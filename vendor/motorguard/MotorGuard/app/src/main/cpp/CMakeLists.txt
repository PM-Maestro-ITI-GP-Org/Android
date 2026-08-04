cmake_minimum_required(VERSION 3.22.1)
project("motorguardvoice")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CORE ${CMAKE_SOURCE_DIR}/assistant-core)

# SQLite is compiled straight into the library (the NDK does not expose the
# platform's libsqlite to apps). Drop the official amalgamation next to this
# file — see cpp/README-native.md for the one-line copy command.
if(NOT EXISTS ${CMAKE_SOURCE_DIR}/sqlite3.c)
    message(FATAL_ERROR
        "Missing cpp/sqlite3.c (+ sqlite3.h).\n"
        "Copy the SQLite amalgamation into app/src/main/cpp/ — see cpp/README-native.md.")
endif()

add_library(motorguardvoice SHARED
    native-lib.cpp
    sqlite3.c
    ${CORE}/src/FaultEvent.cpp
    ${CORE}/src/DiagnosticsEngine.cpp
    ${CORE}/src/IntentMatcher.cpp
    ${CORE}/src/ScoringIntentMatcher.cpp
    ${CORE}/src/Assistant.cpp
)

target_include_directories(motorguardvoice PRIVATE
    ${CORE}/include          # assistant/*.hpp
    ${CMAKE_SOURCE_DIR}      # seed_data.h, sqlite3.h, assistant-core/adapters/...
)

# Keep the binary small: no loadable extensions, no unused SQLite features.
target_compile_definitions(motorguardvoice PRIVATE
    SQLITE_OMIT_LOAD_EXTENSION
    SQLITE_THREADSAFE=1
)

find_library(log-lib log)
target_link_libraries(motorguardvoice ${log-lib})
