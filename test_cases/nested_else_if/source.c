#include "stdio.h"

int main() {
    int x = 3;
    int y = 7;
    int out = 0;

    if (x < 0) {
        out = 1;
    } else if (y > 5) {
        out = y;
    } else {
        out = x;
    }

    return out;
}
