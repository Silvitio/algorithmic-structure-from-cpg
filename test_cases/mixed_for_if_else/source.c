#include "stdio.h"

int main() {
    int sum = 0;

    for (int i = 0; i < 6; i++) {
        if (i % 2 == 0) {
            sum = sum + i;
        } else {
            sum = sum - 1;
        }
    }

    return sum;
}
