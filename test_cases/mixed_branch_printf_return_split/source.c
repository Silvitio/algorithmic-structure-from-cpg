#include "stdio.h"

int main() {
    int x = 1;
    int y = 2;
    int out = 0;

    if (x > 0) {
        out = y + 10;
        printf("%d\n", out);
    } else {
        out = y - 10;
    }

    return out;
}
