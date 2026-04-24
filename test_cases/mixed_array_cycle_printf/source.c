#include "stdio.h"

int main() {
    int arr[5] = {1, 2, 3, 4, 5};
    int index = 0;
    int value = 0;
    int sum = 0;
    int i = 0;

    scanf("%d %d", &index, &value);
    arr[index] = value;

    while (i < 5) {
        sum = sum + arr[i];
        i++;
    }

    printf("%d\n", sum);
    return sum;
}
