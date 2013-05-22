#include "library2.hpp"
#include "library1.hpp"
#include "check.hpp"

#define STRINGIFY_HELPER(ARG) #ARG
#define STRINGIFY( ARG ) STRINGIFY_HELPER(ARG)

std::vector<std::string> catVec( const std::vector<std::string>& bippy )
{
    std::vector<std::string> res;
    for ( std::vector<std::string>::const_iterator it = bippy.begin(); it != bippy.end(); ++it )
    {
        res.push_back( stringAdd( *it, *it ) );
    }
    
    return res;
}

int conditionalFlagCheck() { return THING; }

std::string compiler() { return STRINGIFY(COMPILER); }

std::string targetPlatform() { return STRINGIFY(TARGET_PLATFORM); }


