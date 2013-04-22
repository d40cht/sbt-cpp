#include "library2.hpp"
#include "check.hpp"

#include <string>
#include <vector>
#include <iostream>

int main( int /*argc*/, char** /*argv*/ )
{
    std::vector<std::string> foo = { "aa", "bb", "cc", "dd" };
    
    auto res = catVec( foo );
    
    CHECK_EQUAL( res[0], "aaaa" );
    CHECK_EQUAL( res[1], "bbbb" );
    CHECK_EQUAL( res[2], "cccc" );
    CHECK_EQUAL( res[3], "dddd" );
    
// Check a DEFINE set conditionally based on a project option
#ifdef DEBUG
    CHECK_EQUAL( conditionalFlagCheck(), 1 );
#else
    CHECK_EQUAL( conditionalFlagCheck(), 2 );
#endif
    
#ifdef CLANG
    CHECK_EQUAL( compiler(), "AppleTart" );
#else
    CHECK_EQUAL( compiler(), "GnueyGoodness" );
#endif
    CHECK_EQUAL( targetPlatform(), "x86LinusLand" );
    
    return 0;
}
