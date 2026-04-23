#include "stdio.h"

int main() {
    int x = 5;
    int y = 10;
    int z = 0;

    if (x > 0) {
        z = y + 1;
    } else {
        x = 100;
    }

    return z;
}
