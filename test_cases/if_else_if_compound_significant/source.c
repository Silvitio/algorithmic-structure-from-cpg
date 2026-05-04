#include "stdio.h"

int main() {
    int total = 1;
    int x = 1;
    int y = 2;
    int noise = 100;

    if (x < 0) {
        noise = 1;
    } else if (y > 0) {
        total += y;
    } else {
        noise = 2;
    }

    return total;
}
