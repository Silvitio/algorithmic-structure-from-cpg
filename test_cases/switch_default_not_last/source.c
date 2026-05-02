#include "stdio.h"

int main() {
    int selector = 3;
    int a = 5;
    int b = 7;
    int c = 9;

    switch (selector) {
        default:
            return a;

        case 1:
            return b;

        case 2:
            return c;
    }
}
