#include "stdio.h"

int main() {
    int x = 3;
    int y = 10;
    int z = 0;
    int noise = 5;

    while (x > 0) {
        if (y > 0) {
            z = z + 1;
        } else {
            noise = noise + 10;
            x = x + 43;
        }
        x--;
    }

    return z;
}
