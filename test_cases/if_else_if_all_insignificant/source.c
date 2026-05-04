#include "stdio.h"

int main() {
    int x = 1;
    int y = 5;
    int noise = 100;
    int result = 10;

    if (x < 0) {
        noise = 1;
    } else if (y > 0) {
        noise = 2;
    } else {
        noise = 3;
    }

    return result;
}
