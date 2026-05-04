#include "stdio.h"

int main() {
    int x = 1;
    int y = 5;
    int z = 3;
    int result = 0;
    int noise = 100;

    if (x < 0) {
        noise = 1;
    } else if (y > 0) {
        noise = 2;
    } else {
        result = z + x;
    }

    return result;
}
