#pragma once

// internal
int current_token(void* parser);

std::string_view get_text(void* parser);

// internal
enum Token {
	START_OBJECT = 0,
	END_OBJECT = 1,
	START_ARRAY = 2,
	END_ARRAY = 3,
	FIELD_NAME = 4,
	VALUE_STRING = 5,
	VALUE_NUMBER = 6,
	VALUE_BOOLEAN = 7,
	// usually a binary value
	VALUE_EMBEDDED_OBJECT = 7,
	VALUE_NULL = 8
};
