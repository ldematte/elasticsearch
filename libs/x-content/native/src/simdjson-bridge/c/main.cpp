#include <iostream>
#include "simd_json_bridge.h"
#include "simd_json_bridge_internal.h"

int main(void) {
   
	void* factory = create_parser_factory();

    std::string json = "{\"array\":[1,2,3],\"string\":\"abc\"}";
    json.reserve(json.length() + 64);
	void* parser = create_parser(factory, json.data(), (int32_t)json.length(), (int32_t)json.size());

    int matches = 0;
    while (next_token(parser) != TOKEN_END) {
        if (current_token(parser) == Token::VALUE_STRING) {
            int size;
            auto ptr = string_value(parser, &size);
            std::string value(ptr, size);
            if (value == "foo") {
                ++matches;
            }
        }
    }

	delete_parser(parser);
	delete_parser_factory(factory);
}

