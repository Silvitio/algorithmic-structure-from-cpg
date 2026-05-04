#include "stdio.h"

int main() {
    int x = 1;
    int y = 5;
    int a = 11;
    int b = 22;
    int c = 33;
    int result = 0;

    if (x < 0) {
        result = a;
    } else if (y > 0) {
        result = b;
    } else {
        result = c;
    }

    return result;
}
