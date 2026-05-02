#include "stdio.h"

int main() {
    int selector = 1;
    int x = 10;
    int y = 20;

    switch (selector) {
        case 1:
            return x;

        default:
            return y;
    }
}
