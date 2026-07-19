#include "demosaic.h"
#include <vector>
#include <algorithm>

void demosaic_amaze(const DemosaicParams& p, void* pixels) {
    demosaic_rcd(p, pixels);
}
