#include "stdio.h"

int main() {
    int x = 3;
    int result = 10;

    do {
        if (x == 2) {
            result = result + x;
        }
        x--;
    } while (x > 0);

    return result;
}
