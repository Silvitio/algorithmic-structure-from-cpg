#include "stdio.h"

int main() {
    int selector = 0;
    int noise = 1;
    int result = 10;

    switch (selector) {
        case 1:
            noise = 2;
            break;

        default:
            printf("%d\n", result);
            break;
    }

    return 0;
}
