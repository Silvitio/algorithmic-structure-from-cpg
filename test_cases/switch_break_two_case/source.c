#include "stdio.h"

int main() {
    int selector = 1;
    int x = 10;
    int y = 20;

    switch (selector) {
        case 1:
            y++;
            break;

        case 2:
            x++;
            break;
    }

    return y + x;
}
