#include "stdio.h"

int main() {
    int a = 10;
    int b = 2;
    int c = 3;
    int d = 20;
    int e = 9;

    a += b;
    c *= a;
    d /= b;
    e %= b;
    d -= e;

    return c + d;
}
