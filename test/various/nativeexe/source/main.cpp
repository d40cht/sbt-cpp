#include <iostream>

#include "library2.hpp"
#include "library1.hpp"
#include "check.hpp"

int main( int, char** )
{
    CHECK_EQUAL( true, true );
    std::cout << "Hello world: " << compiler() << std::endl;
}

