#include "stdio.h"

int main() {
    int selector = 2;
    int x = 10;
    int y = 20;
    int result = 0;

    switch (selector) {
    case 1:
        result = x;
        break;

    case 2: {
        result = y + 1;
        printf("%d\n", result);
        break;
    }

    default:
        result = x + y;
        return result;
    }

    return result;
}