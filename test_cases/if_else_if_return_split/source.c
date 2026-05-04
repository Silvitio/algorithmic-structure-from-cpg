#include "stdio.h"

int main() {
    int x = 1;
    int y = 5;
    int result = 0;
    int noise = 100;

    if (x < 0) {
        noise = 1;
    } else if (y > 0) {
        return y;
    } else {
        result = x;
    }

    return result;
}
