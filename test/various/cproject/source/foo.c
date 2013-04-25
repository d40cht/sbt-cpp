
// Not a legal symbol name in C++, only in C
struct template
{
    int x;
};

int obfuscatedAdd( int a, int b )
{
    struct template p, q;
    p.x = a;
    q.x = b;
    
    return p.x + q.x;
}
