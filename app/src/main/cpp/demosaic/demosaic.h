#ifndef WCAM_DEMOSAIC_H
#define WCAM_DEMOSAIC_H

#include "demosaic_common.h"

// Common interface for parallel execution
typedef void (*DemosaicFunc)(const DemosaicParams& p, int* pixels);

void demosaic_bilinear(const DemosaicParams& p, int* pixels);
void demosaic_ahd(const DemosaicParams& p, int* pixels);
void demosaic_vng(const DemosaicParams& p, int* pixels);
void demosaic_rcd(const DemosaicParams& p, int* pixels);
void demosaic_amaze(const DemosaicParams& p, int* pixels);
void demosaic_lmmse(const DemosaicParams& p, int* pixels);
void demosaic_dcb(const DemosaicParams& p, int* pixels);

#endif // WCAM_DEMOSAIC_H
