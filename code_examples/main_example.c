#include "stdio.h"

int main() {
    int selector = 2;
    int x = 10;
    int y = 20;
    int result = 0;
    int noise = 15;

    switch (selector) {
        case 1:
            x++;
            return result;

        case 2: {
            noise = x * y;
            break;
        }

        default:
            result = x + y;
            break;
    }

    result = x;
    x = 15 + y;

    return result;
}
