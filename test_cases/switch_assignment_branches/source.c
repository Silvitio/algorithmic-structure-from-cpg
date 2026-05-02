#include "stdio.h"

int main() {
    int selector = 2;
    int x = 10;
    int y = 20;
    int result = 0;

    switch (selector) {
        case 1:
            x++;
            break;

        case 2: {
            result = y + 1;
            break;
        }

        default:
            result = x + y;
            break;
    }

    return result;
}
