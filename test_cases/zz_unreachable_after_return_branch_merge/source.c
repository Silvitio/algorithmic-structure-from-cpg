#include "stdio.h"

int main() {
    int x = 1;
    int y = 2;

    if (x < 0) {
        y = 3;
        return y;
        y = 4;
    }

    return y;
}
