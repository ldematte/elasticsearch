#include <iostream>
#include "simd_json_bridge.h"
#include "simd_json_bridge_internal.h"

int main(void) {
   
	void* factory = create_parser_factory();

    std::string json = "{\"array\":[1,2,3],\"string\":\"abc\"}";
	void* parser = create_parser(factory, json);

    int matches = 0;
    while (next_token(parser) != -1) {
        if (current_token(parser) == Token::VALUE_STRING) {
            std::string_view value = get_text(parser);
            if (value == "foo") {
                ++matches;
            }
        }
    }

	delete_parser(parser);
	delete_parser_factory(factory);
}

