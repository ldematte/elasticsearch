#pragma once

#ifdef _MSC_VER
#define EXPORT __declspec(dllexport)
#elif defined(__GNUC__) && !defined(__clang__)
#define EXPORT __attribute__((externally_visible,visibility("default")))
#elif __clang__
#define EXPORT __attribute__((visibility("default")))
#endif

extern "C"
EXPORT void* create_parser_factory();
extern "C"
EXPORT void delete_parser_factory(void* factory);

extern "C"
EXPORT void* create_parser(void* factory, void* data, int32_t data_length, int32_t buffer_length);
extern "C"
EXPORT void delete_parser(void* state);

extern "C"
EXPORT int32_t next_token(void* parser);
extern "C"
EXPORT const char* current_name(void* current_state, int32_t* size);

extern "C"
EXPORT int64_t long_value(void* parser);
extern "C"
EXPORT int32_t boolean_value(void* parser);
extern "C"
EXPORT double double_value(void* parser);
extern "C"
EXPORT const char* string_value(void* current_state, int32_t* size);
