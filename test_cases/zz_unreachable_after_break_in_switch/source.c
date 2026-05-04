#include "stdio.h"

int main() {
    int selector = 1;
    int x = 0;

    switch (selector) {
        case 1:
            x = 5;
            break;
            x = 7;
        default:
            x = 9;
            break;
    }

    return x;
}
