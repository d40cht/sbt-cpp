#pragma once

#include <sstream>
#include <stdexcept>
#include <iostream>

template<typename A, typename B>
inline void check_equal( const char* FILE, int line, const A& lhs, const B& rhs )
{
    if ( lhs != rhs )
    {
        std::stringstream ss;
        ss << "Test check failure: (" << FILE << ", " << line << "): " << lhs << " != " << rhs;
        std::cerr << ss.str() << std::endl;
        throw std::runtime_error( ss.str() );
    }
}

inline void check( const char* FILE, int line, bool predicate, const char* predicateString )
{
    if ( !predicate )
    {
        std::stringstream ss;
        ss << "Test check failure: (" << FILE << ", " << line << "): " << predicateString;
        std::cerr << ss.str() << std::endl;
        throw std::runtime_error( ss.str() );
    }
}

#define CHECK_EQUAL( lhs, rhs ) check_equal( __FILE__, __LINE__, (lhs), (rhs) )
#define CHECK( predicate ) check( __FILE__, __LINE__, (predicate), #predicate )

class AssertionFailureException : public std::exception
{
public:
    AssertionFailureException( const char* message ) : m_message(message)
    {
    }
    
    const char* what() const throw() { return m_message; }
    
private:
    const char* m_message;
};

