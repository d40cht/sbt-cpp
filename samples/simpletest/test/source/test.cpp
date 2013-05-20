#include "library.hpp"
#include "check.hpp"

int main( int argc, char** argv )
{
    try
    {
        CHECK_EQUAL( multiply( 3, 4 ), 12 );
        CHECK_EQUAL( multiply( 7, 9 ), 63 );
        CHECK_EQUAL( multiply( 1024, 1024 ), 1048576 );
    }
    catch ( std::exception& e )
    {
        std::cerr << "Test failed: " << e.what() << std::endl;
        return -1;
    }
    
    std::cerr << "All tests passed" << std::endl;
    return 0;
}

