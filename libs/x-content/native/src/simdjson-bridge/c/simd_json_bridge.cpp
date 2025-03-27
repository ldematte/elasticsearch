// Compile with:
// g++ -std=c++20 -shared -fpic -o libsimdjson-bridge.dylib -I src/simdjson-bridge/headers/ src/simdjson-bridge/c/simd_json_bridge.cpp src/simdjson-bridge/c/simdjson.cpp -O3
// cp ./libsimdjson-bridge.dylib ../../native/libraries/build/platform/darwin-aarch64
// To verify:
// objdump --macho --exports-trie ./libsimdjson-bridge.dylib

#include "simdjson.h"
#include "simd_json_bridge.h"
#include "simd_json_bridge_internal.h"

using namespace simdjson;

extern "C"
EXPORT void* create_parser_factory() {
	return (void*) new ondemand::parser();
}

extern "C"
EXPORT void delete_parser_factory(void* factory) {
	ondemand::parser* parser = (ondemand::parser*)factory;
	delete parser;
}

// private
static int get_token_type(const ondemand::json_type& type) {
	switch (type) {
	case ondemand::json_type::array:
		return START_ARRAY;
	case ondemand::json_type::object:
		return START_OBJECT;
	case ondemand::json_type::number:
		return VALUE_NUMBER;
	case ondemand::json_type::string:
		return VALUE_STRING;
	case ondemand::json_type::boolean:
		return VALUE_BOOLEAN;
	case ondemand::json_type::null:
	    return VALUE_NULL;
	}
    return -1;
}

struct parser_state;

class Continuation {
public:
	virtual Continuation* run(parser_state* state) = 0;
};

static Continuation* bind(Continuation* next, parser_state* state);

struct parser_state {
	ondemand::document doc;
	ondemand::value current_value;
	int current_token;
	std::string_view current_name;
	std::vector<Continuation*> stack;
	Continuation* next;
};

// private
struct InArray : public Continuation {
private:
	ondemand::array_iterator iterator;
	ondemand::array_iterator end;
	bool need_advance;
public:
	InArray(const simdjson_result<ondemand::array_iterator>& begin, const simdjson_result<ondemand::array_iterator>& end)
		: iterator(begin.value_unsafe()), end(end.value_unsafe()), need_advance(false) {

	}

	virtual Continuation* run(parser_state* state) {
		if (need_advance == false) {
			need_advance = true;
		}
		else {
			++iterator;
		}
		if (iterator != end) {
			state->current_value = *iterator; //std::move?
			state->current_token = get_token_type(state->current_value.type());
			return bind(this, state);
		}
		else {
			state->current_token = END_ARRAY;
			auto top = state->stack.back();
			state->stack.pop_back();
			delete this;
			return top;
		}
	}
};

struct InObject : public Continuation {
private:
	ondemand::object_iterator iterator;
	ondemand::object_iterator end;
	int value_type;
	bool need_advance;
public:
	InObject(simdjson_result<ondemand::object>& object) : iterator(object.begin()), end(object.end()), value_type(-1), need_advance(false) {}

	Continuation* run(parser_state* state) {
		if (value_type != -1) {
			state->current_token = value_type;
			value_type = -1;
			return bind(this, state);
		}
		if (need_advance == false) {
			need_advance = true;
		}
		else {
			++iterator;
		}
		if (iterator != end) {
			auto field = *iterator;
			state->current_token = FIELD_NAME;
			state->current_name = field.escaped_key();
			state->current_value = field.value(); // std::move?
			value_type = get_token_type(state->current_value.type());
			return bind(this, state);
		}
		else {
			state->current_token = END_OBJECT;
			auto top = state->stack.back();
			state->stack.pop_back();
			delete this;
			return top;
		}
	}
};


class End : public Continuation {
public:
	virtual Continuation* run(parser_state* state) {
		state->current_token = -1;
		state->next = bind(this, state);
		return this;
	}
};

End END;

struct Init : public Continuation {
	Continuation* run(parser_state* state) {
		state->current_value = state->doc.get_value();
		state->current_token = get_token_type(state->current_value.type());
		return bind(&END, state);
	}
};

Init INIT;

// private
static Continuation* bind(Continuation* next, parser_state* state) {
	if (state->current_token == START_OBJECT) {
		state->stack.push_back(next);
		auto obj = state->current_value.get_object();
		return new InObject(obj);
	}
	else if (state->current_token == START_ARRAY) {
		state->stack.push_back(next);
		auto array = state->current_value.get_array();
		auto begin = array.begin();
		auto end = array.end();
		return new InArray(begin, end);
	}
	else {
		return next;
	}
}

extern "C"
EXPORT void* create_parser(void* factory, void* padded_data, int32_t data_length, int32_t buffer_length) {
	ondemand::parser* parser = (ondemand::parser*)factory;
	parser_state* state = new parser_state();

	state->doc = parser->iterate((const uint8_t *) padded_data, (size_t) data_length, (size_t) buffer_length);
	state->next = &INIT;

	return state;
}

extern "C"
EXPORT void delete_parser(void* current_state) {
	parser_state* state = (parser_state*)current_state;
	delete state;
}

extern "C"
EXPORT int32_t next_token(void* current_state) {
	parser_state* state = (parser_state*)current_state;
	state->next = state->next->run(state);
	return state->current_token;
}

extern "C"
EXPORT const char* current_name(void* current_state, int32_t* size) {
	parser_state* state = (parser_state*)current_state;
	*size = state->current_name.length();
	return state->current_name.data();
}

extern "C"
EXPORT int64_t long_value(void* current_state) {
   parser_state* state = (parser_state*)current_state;
   return state->current_value.get_int64();
}

extern "C"
EXPORT int32_t boolean_value(void* current_state) {
    parser_state* state = (parser_state*)current_state;
    return state->current_value.get_bool() ? 1 : 0;
}

extern "C"
EXPORT double double_value(void* current_state) {
    parser_state* state = (parser_state*)current_state;
    return state->current_value.get_double();
}

extern "C"
EXPORT const char* string_value(void* current_state, int32_t* size) {
	parser_state* state = (parser_state*)current_state;
	std::string_view string_view = state->current_value.get_string();
	*size = string_view.length();
	return string_view.data();
}

std::string_view get_text(void* current_state) {
	parser_state* state = (parser_state*)current_state;
	return state->current_value.get_string();
}

// internal
int current_token(void* current_state) {
	parser_state* state = (parser_state*)current_state;
	return state->current_token;
}

