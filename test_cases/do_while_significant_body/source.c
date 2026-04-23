#include "stdio.h"

int main() {
    int x = 3;
    int sum = 0;

    do {
        sum = sum + x;
        x--;
    } while (x > 0);

    return sum;
}
