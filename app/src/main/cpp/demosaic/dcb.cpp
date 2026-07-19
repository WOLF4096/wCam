#include "demosaic.h"
#include <vector>
#include <algorithm>

void demosaic_dcb(const DemosaicParams& p, void* pixels) {
    demosaic_rcd(p, pixels);
}
