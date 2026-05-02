#include "stdio.h"

int main() {
    int selector = 2;
    int x = 10;
    int y = 20;

    switch (selector) {
        case 1: {
            printf("%d\n", x);
            break;
        }

        default: {
            printf("%d\n", y);
            break;
        }
    }

    return 0;
}
