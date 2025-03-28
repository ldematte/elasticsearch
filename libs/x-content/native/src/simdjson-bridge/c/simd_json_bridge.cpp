
#include "simdjson.h"
#include "simdjson.cpp"
#include "simd_json_bridge.h"
#include "simd_json_bridge_internal.h"

using namespace simdjson;

// private
// TODO: use a lookup table
static int get_token_type(const uint8_t* value) {
	// Use the fact that most scalars are going to be either strings or numbers.
	if (*value == '"') {
		return VALUE_STRING;
	}
	else if (((*value - '0') < 10) || (*value == '-')) {
		return VALUE_NUMBER;
	}
	// true, false, null are uncommon.
	switch (*value) {
	case '{': return START_OBJECT;
	case '}': return END_OBJECT;
	case '[': return START_ARRAY;
	case ']': return END_ARRAY;
	case 't': return VALUE_BOOLEAN;
	case 'f': return VALUE_BOOLEAN;
	case 'n': return VALUE_NULL;
	default:
		return TOKEN_ERROR;
	}
}

class ParserState {
public:
	int current_token;
	const uint8_t* current_value;
	std::string_view current_name;
	
	std::unique_ptr<uint8_t[]> current_name_buffer{};
	std::unique_ptr<uint8_t[]> current_value_buffer{};
	size_t capacity{};
	std::unique_ptr<internal::dom_parser_implementation> parser{};
	std::unique_ptr<builtin::stage2::json_iterator> iterator{};

	std::string last_error{};
	std::vector<bool> scopes{};

	error_code allocate(size_t new_capacity);
};

// private
error_code ParserState::allocate(size_t new_capacity) {
	// string_capacity copied from document::allocate
	size_t string_capacity = SIMDJSON_ROUNDUP_N(5 * new_capacity / 3 + SIMDJSON_PADDING, 64);
	current_name_buffer.reset(new (std::nothrow) uint8_t[string_capacity]);
	current_value_buffer.reset(new (std::nothrow) uint8_t[string_capacity]);
	if (auto error = parser->set_capacity(new_capacity)) { 
		return error; 
	}
	capacity = new_capacity;
	return SUCCESS;
}

extern "C"
EXPORT void* create_parser_factory() {
	return (void*) new ParserState();
}

extern "C"
EXPORT void delete_parser_factory(void* parser_state) {
	ParserState* state = (ParserState*)parser_state;
	delete state;
}


extern "C"
EXPORT void* create_parser(void* parser_state, void* data, int32_t data_length, int32_t buffer_length) {

	ParserState* state = (ParserState*)parser_state;

	error_code error;
	if (state->parser == NULL) {
		if (error = simdjson::get_active_implementation()->create_dom_parser_implementation(buffer_length, DEFAULT_MAX_DEPTH, state->parser)) {
			state->last_error = error_message(error);
			return NULL;
		}
		if (error = state->parser->set_max_depth(DEFAULT_MAX_DEPTH)) {
			state->last_error = error_message(error);
			return NULL;
		}
	}
	if (error = state->allocate(buffer_length)) {
		state->last_error = error_message(error);
		return NULL;
	}

	if (error = state->parser->stage1((uint8_t*)data, data_length, stage1_mode::regular)) {
		state->last_error = error_message(error);
		return NULL;
	}
		
	state->iterator.reset(new builtin::stage2::json_iterator(*((builtin::dom_parser_implementation*)state->parser.get()), 0));
	state->current_token = TOKEN_BEGIN;

	return state;
}

extern "C"
EXPORT void delete_parser(void* parser_state) {
}

// private
static int next_valid_token(ParserState* state) {
	auto in_object = state->scopes[state->scopes.size() - 1];
	if (in_object) {
		switch (*state->iterator->advance()) {
		case ',': {
			const uint8_t* value = state->iterator->advance();
			if (simdjson_unlikely(*value != '"')) {
				state->last_error = "Key string missing at beginning of field in object";
				return TOKEN_ERROR;
			}
			else {
				auto ptr = state->current_name_buffer.get();
				auto position = builtin::stringparsing::parse_string(value + 1, ptr, false);
				state->current_name = std::string_view((const char*)ptr, (size_t)(position - ptr));
				return FIELD_NAME;
			}
		} break;
		case '}':
			return END_OBJECT;
		default:
			state->last_error = "Missing comma between object fields";
			return TOKEN_ERROR;
		}
	} else {
		switch (*state->iterator->advance()) {
		case ',': {
			const uint8_t* value = state->iterator->advance();
			state->current_value = value;
			return get_token_type(value);
		} break;
		case ']':
			return END_ARRAY;
		default:
			state->last_error = "Missing comma between array values";
			return TOKEN_ERROR;
		}
	}
}

extern "C"
EXPORT int next_token(void* parser_state) {
	ParserState* state = (ParserState*)parser_state;

	switch (state->current_token) {
	case TOKEN_END:
	case TOKEN_ERROR:
		break;
	case TOKEN_BEGIN: {
		const uint8_t* value = state->iterator->advance();
		switch (*value) {
		case '{':
			if (state->iterator->last_structural() != '}') {
				state->last_error = "starting brace unmatched";
				state->current_token = TOKEN_ERROR;
			}
			else {
				state->current_token = START_OBJECT;
			}
			break;
		case '[':
			if (state->iterator->last_structural() != ']') {
				state->last_error = "starting bracket unmatched";
				state->current_token = TOKEN_ERROR;
			}
			else {
				state->current_token = START_ARRAY;
			}
			break;
		default:
			state->current_value = value;
			state->current_token = get_token_type(value);
			break;
		}
	} break;
	case START_OBJECT: {
		const uint8_t* value = state->iterator->advance();
		state->scopes.push_back(true);
		if (simdjson_unlikely(*value != '"')) {
			state->last_error = "Object does not start with a key";
			state->current_token = TOKEN_ERROR;
		}
		else {
			state->current_token = FIELD_NAME;
			auto ptr = state->current_name_buffer.get();
			auto position = builtin::stringparsing::parse_string(value + 1, ptr, false);
			state->current_name = std::string_view((const char*)ptr, (size_t)(position - ptr));
		}
	} break;
	case FIELD_NAME: {
		if (simdjson_unlikely(*(state->iterator->advance()) != ':')) {
			state->last_error = "Missing colon after key in object";
			state->current_token = TOKEN_ERROR;
		}
		else {
			const uint8_t* value = state->iterator->advance();
			state->current_value = value;
			state->current_token = get_token_type(value);
		}
	} break;
	case START_ARRAY: {
		state->scopes.push_back(false);
		const uint8_t* value = state->iterator->advance();
		state->current_value = value;
		state->current_token = get_token_type(value);
	} break;
	case END_OBJECT:
	case END_ARRAY: {
		state->scopes.pop_back();
		if (state->scopes.empty()) {
			state->current_token = TOKEN_END;
		}
		else {
			state->current_token = next_valid_token(state);
		}
	} break;
	default:
		state->current_token = next_valid_token(state);
		break;
	}

	return state->current_token;
}

extern "C"
EXPORT const char* current_name(void* parser_state, int32_t* size) {
	ParserState* state = (ParserState*)parser_state;
	*size = (int32_t)state->current_name.length();
	return state->current_name.data();
}

extern "C"
EXPORT int32_t boolean_value(void* parser_state) {
	ParserState* state = (ParserState*)parser_state;
	auto value = state->current_value;
	if (*value == 't') {
		return 1;
	}
	if (*value == 'f') {
		return 0;
	}
	state->last_error = "Invalid boolean value";
	return 0;
}

extern "C"
EXPORT int64_t long_value(void* parser_state) {
	ParserState* state = (ParserState*)parser_state;
	int64_t result;
	if (auto error = builtin::numberparsing::parse_integer(state->current_value).get(result)) {
		state->last_error = error_message(error);
	}
	return result;
}

extern "C"
EXPORT double double_value(void* parser_state) {
	ParserState* state = (ParserState*)parser_state;
	double result;
	if (auto error = builtin::numberparsing::parse_double(state->current_value).get(result)) {
		state->last_error = error_message(error);
	}
	return result;
}

extern "C"
EXPORT const char* string_value(void* parser_state, int32_t* size) {
	ParserState* state = (ParserState*)parser_state;
	// TODO: consider passing this in?
	auto ptr = state->current_value_buffer.get();
	auto position = builtin::stringparsing::parse_string(state->current_value + 1, ptr, false);
	*size = (int32_t)(position - ptr);
	return (const char*)ptr;
}

// internal
int current_token(void* parser_state) {
	ParserState* state = (ParserState*)parser_state;
	return state->current_token;
}

