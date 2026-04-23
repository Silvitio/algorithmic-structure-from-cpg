#include "stdio.h"

int main() {
    int a = 1;
    int b = 2;
    int res = 0;

    if (a < b) {
        res = a + 10;
    } else {
        res = b + 20;
    }

    printf("%d\n", res);
    return res;
}
