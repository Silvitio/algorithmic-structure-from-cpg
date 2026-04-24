#include "stdio.h"

int main() {
    int arr[4] = {1, 2, 3, 4};
    int index = 0;
    int value = 0;
    int out = 0;

    scanf("%d %d", &index, &value);
    arr[index] = value;

    if (arr[2] > 2) {
        out = arr[index];
    } else {
        out = arr[0];
    }

    return out;
}
