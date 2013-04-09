#include "library2.hpp"
#include "library1.hpp"

std::vector<std::string> catVec( const std::vector<std::string>& bippy )
{
    std::vector<std::string> res;
    for ( auto& s : bippy )
    {
        res.push_back( stringAdd( s, s ) );
    }
    
    return res; 
}

int conditionalFlagCheck() { return THING; }

std::string compiler() { return COMPILER; }

std::string targetPlatform() { return TARGET_PLATFORM; }


