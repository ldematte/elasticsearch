#pragma once

void* create_parser_factory();
void delete_parser_factory(void* factory);

void* create_parser(void* factory, std::string& data);
void delete_parser(void* state);

int next_token(void* parser);


