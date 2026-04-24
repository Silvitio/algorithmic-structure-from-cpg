#include "stdio.h"

int main() {
    int a = 5;
    int b = 2;
    int out = 0;
    int noise = 100;

    if (a > 0) {
        if (b > 0) {
            out = a + b;
        } else {
            out = a;
        }
    } else {
        noise = 7;
    }

    printf("%d\n", out);
    return out;
}
