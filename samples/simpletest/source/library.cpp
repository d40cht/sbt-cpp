#include "library.hpp"

unsigned int multiply( unsigned int a, unsigned int b )
{
    unsigned int acc = 0;
    unsigned int multiplier = a;
    for ( int i = 0; i < (sizeof(unsigned int) / sizeof(char))*8; ++i )
    {
        if ( (b & 1) ) acc += multiplier;
        b >>= 1;
        multiplier <<= 1;
    }
    
    return acc;
}

