#include "stdio.h"

int main() {
    int x = 0;
    int sum = 0;
    int i = 0;
    int noise = 100;

    scanf("%d", &x);

    if (x > 0) {
        while (i < x) {
            sum = sum + i;
            i++;
        }
    } else {
        noise = 200;
    }

    printf("%d\n", sum);
    return sum;
}
