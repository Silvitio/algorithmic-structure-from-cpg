#include "stdio.h"

int main() {
    int i = 0;
    int sum = 0;

    while (i < 4) {
        sum = sum + i;
        i++;
    }

    return sum;
}
